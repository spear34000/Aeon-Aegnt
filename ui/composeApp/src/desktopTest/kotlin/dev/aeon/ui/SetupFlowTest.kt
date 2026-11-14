package dev.aeon.ui

import dev.aeon.ui.api.AeonClient
import dev.aeon.ui.api.Profile
import dev.aeon.ui.platform.DesktopDeviceCrypto
import dev.aeon.ui.platform.KeyValueStore
import dev.aeon.ui.platform.LocalAgentHost
import dev.aeon.ui.platform.localAgentHost
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.Consents
import dev.aeon.ui.state.INTRO_PAGES
import dev.aeon.ui.state.SetupStep
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** First-run flow: order of the steps, going back, skipping, resuming after the app is closed, and a store that cannot write. */
class SetupFlowTest {
    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() = cleanup.reversed().forEach { runCatching(it) }

    private class MapStore(val map: MutableMap<String, String> = mutableMapOf(), var failWrites: Boolean = false) : KeyValueStore {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            if (failWrites) throw java.io.IOException("disk full")
            map[key] = value
        }
        override fun remove(key: String) {
            if (failWrites) throw java.io.IOException("disk full")
            map.remove(key)
        }
    }

    /** A model whose server address answers nothing: these tests never need the network. */
    private fun model(store: KeyValueStore, agent: LocalAgentHost = localAgentHost()): AppModel {
        val dir = Files.createTempDirectory("aeon-setup").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient()
        cleanup += { scope.cancel(); http.close(); dir.deleteRecursively() }
        val crypto = DesktopDeviceCrypto(dir)
        return AppModel(AeonClient("http://127.0.0.1:9", crypto, http), store, crypto, scope, defaultServerUrl = "", localAgent = agent).also { it.start() }
    }

    private val AppModel.setup get() = state.value.setup

    @Test
    fun aNewInstallWalksTheStepsInOrderAndCanGoBack() {
        val m = model(MapStore())
        assertTrue(m.setup.loaded)
        assertEquals(SetupStep.Welcome, m.setup.step, "the title page comes first")
        assertFalse(m.setupBack(), "nothing before the title page: the system handles back")
        m.startIntro()
        assertEquals(SetupStep.Intro, m.setup.step)
        assertTrue(m.setupBack(), "back from the first intro page returns to the title page")
        assertEquals(SetupStep.Welcome, m.setup.step)
        m.startIntro()

        m.setIntroPage(2)
        assertTrue(m.setupBack())
        assertEquals(1, m.setup.introPage)
        m.finishIntro() // also what "건너뛰기" does
        assertEquals(SetupStep.Terms, m.setup.step)
        m.setupBack()
        assertEquals(SetupStep.Intro to INTRO_PAGES - 1, m.setup.step to m.setup.introPage, "back from the terms reopens the last intro page")
        m.finishIntro()
        assertEquals(SetupStep.Terms, m.setup.step)

        m.acceptTerms()
        assertEquals(SetupStep.Terms, m.setup.step, "cannot continue without the required agreements")
        m.setConsents(Consents(service = true, data = false, online = true))
        m.acceptTerms()
        assertEquals(SetupStep.Terms, m.setup.step, "one of two required items is not enough, whatever the optional ones say")
        m.setConsents(Consents(service = true, data = true))
        m.acceptTerms()
        assertEquals(SetupStep.Where, m.setup.step, "an unpaired device first says where the agent runs")
        assertTrue(m.setup.consents.acceptedAt > 0)
        assertFalse(m.setup.consents.online, "optional items stay off unless chosen")
        m.setupBack()
        assertEquals(SetupStep.Terms, m.setup.step)
    }

    @Test
    fun bothWaysAreFirstClass_anotherDeviceGetsItsGuideThenTheForm_thisDeviceSaysWhatIsPossible() {
        val store = MapStore()
        val m = model(store)
        m.startIntro(); m.finishIntro()
        m.setConsents(Consents(service = true, data = true)); m.acceptTerms()
        assertEquals(SetupStep.Where, m.setup.step)
        assertEquals(null, m.setup.where, "no choice is made for the owner")

        m.chooseWhere("remote")
        assertEquals("remote", model(store).setup.where, "the choice is remembered")
        m.whereContinue()
        assertEquals(SetupStep.Connect to false, m.setup.step to m.setup.guideDone, "the guide comes before the address and code form")
        m.guideContinue()
        assertTrue(m.setup.guideDone)
        m.setupBack()
        assertEquals(SetupStep.Connect to false, m.setup.step to m.setup.guideDone, "back from the form returns to the guide")
        m.setupBack()
        assertEquals(SetupStep.Where, m.setup.step, "back from the guide returns to the choice")

        m.chooseWhere("device")
        m.whereContinue()
        assertEquals("device", m.setup.where)
        assertEquals(SetupStep.Connect, m.setup.step, "this device's page is part of the same step and says what works today")
        m.chooseWhere("remote") // what its button does
        m.whereContinue()
        assertEquals("remote", m.setup.where)
    }


    /** An agent inside the app that does what the test says. */
    private class FakeAgent(val startResult: String?, val code: String?, override val available: Boolean = true) : LocalAgentHost {
        var started = 0
        override suspend fun start(): String? { started++; return startResult }
        override suspend fun pairingCode(): String? = code
    }

    private fun AppModel.waitForDevice() = runBlocking { withTimeout(10_000) { while (setup.deviceStarting) delay(20) } }

    @Test
    fun thisDevicePathStartsTheAgentAndSaysWhyWhenItCannot() {
        val none = model(MapStore(), FakeAgent(null, null, available = false))
        assertFalse(none.canRunOnDevice, "a build without the agent says so")

        val broken = FakeAgent("에이전트가 시작되지 않았어요.", null)
        val m = model(MapStore(), broken)
        assertTrue(m.canRunOnDevice)
        m.startIntro(); m.finishIntro()
        m.setConsents(Consents(service = true, data = true)); m.acceptTerms()
        m.chooseWhere("device")
        m.whereContinue()
        assertEquals(SetupStep.Connect, m.setup.step)
        m.waitForDevice()
        assertEquals("에이전트가 시작되지 않았어요.", m.setup.deviceError, "the reason is shown, with a retry")
        assertEquals(1, broken.started)

        val noCode = FakeAgent(null, null)
        val m2 = model(MapStore(), noCode)
        m2.startIntro(); m2.finishIntro()
        m2.setConsents(Consents(service = true, data = true)); m2.acceptTerms()
        m2.chooseWhere("device"); m2.whereContinue(); m2.waitForDevice()
        assertEquals("이 기기의 에이전트에서 연결 코드를 받지 못했어요.", m2.setup.deviceError)
        m2.startOnDevice(); m2.waitForDevice()
        assertEquals(2, noCode.started, "retry starts again")

        val other = FakeAgent(null, null)
        val m3 = model(MapStore(), other)
        m3.startIntro(); m3.finishIntro()
        m3.setConsents(Consents(service = true, data = true)); m3.acceptTerms()
        m3.chooseWhere("remote"); m3.whereContinue()
        assertEquals(0, other.started, "choosing another computer never starts the agent here")
    }

    @Test
    fun aScannedLinkSkipsTheChoiceBecauseItAlreadyMeansAnotherDevice() {
        val m = model(MapStore())
        m.receivePairLink("aeon://pair?u=http%3A%2F%2F127.0.0.1%3A9&c=75KFXQWOXQGQ7VLQ")
        m.setConsents(Consents(service = true, data = true))
        m.acceptTerms()
        assertEquals(SetupStep.Connect, m.setup.step)
        assertEquals("remote" to true, m.setup.where to m.setup.guideDone, "straight to the form with the address and code filled in")
    }

    @Test
    fun progressAndChoicesSurviveClosingTheApp() {
        val store = MapStore()
        val first = model(store)
        first.startIntro()
        first.setIntroPage(3)
        first.finishIntro()
        first.setConsents(Consents(service = true, data = true, background = true))
        first.editSetupProfile(Profile(userName = "지우", tone = "casual", interests = listOf("개발", "여행")))
        first.setProfilePage(2)

        val again = model(store) // the app was closed and opened again
        assertEquals(SetupStep.Terms, again.setup.step)
        assertEquals(3, again.setup.introPage)
        assertEquals(Consents(service = true, data = true, background = true), again.setup.consents)
        assertEquals(Profile(userName = "지우", tone = "casual", interests = listOf("개발", "여행")), again.setup.profileDraft)
        assertEquals(2, again.setup.profilePage)
        assertEquals("지우", again.state.value.profile.userName, "before the server is reached the greeting uses what was typed here")
    }

    @Test
    fun skippingTheProfileOrFinishingItEmptyOpensTheMainScreenAndIsRememberedAsDone() {
        val store = MapStore()
        val m = model(store)
        m.startIntro(); m.finishIntro()
        m.setConsents(Consents(service = true, data = true)); m.acceptTerms()
        store.map["setup.step"] = SetupStep.Profile.name // as if pairing and the tour were done
        val p = model(store)
        assertEquals(SetupStep.Profile, p.setup.step)
        p.finishProfile() // nothing typed: there is nothing to send, so no server is needed
        assertEquals(SetupStep.Done, p.setup.step)
        assertEquals(SetupStep.Done, model(store).setup.step, "the first run happens once")

        // Taking back a required agreement returns to the terms; agreeing again does not repeat the tour.
        val w = model(store)
        w.withdrawTerms()
        assertEquals(SetupStep.Terms, w.setup.step)
        assertFalse(w.setup.consents.requiredOk)
        assertEquals(SetupStep.Terms, model(store).setup.step, "still at the terms after a restart")
    }

    @Test
    fun aDevicePairedBeforeThisFlowExistedIsNotSentThroughIt() {
        val store = MapStore(mutableMapOf("serverUrl" to "http://127.0.0.1:9", "deviceId" to "dev_0123456789abcdef01"))
        val m = model(store)
        assertEquals(SetupStep.Done, m.setup.step)
        assertEquals("Done", store.map["setup.step"])
    }

    @Test
    fun aStoreThatCannotWriteDoesNotStopTheFlow() {
        val store = MapStore(failWrites = true)
        val m = model(store)
        m.startIntro()
        m.finishIntro()
        assertEquals(SetupStep.Terms, m.setup.step, "progress continues in memory")
        assertTrue(m.setup.storageError, "and the screen can say that it will not be remembered")
        m.editSetupProfile(Profile(userName = "지우"))
        assertEquals("지우", m.setup.profileDraft.userName)
    }

    private fun waitFor(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(cond(), "condition not reached in time")
    }

    @Test
    fun aScannedLinkOnlyPrefillsTheConnectScreenAndMovesAFreshInstallToTheTerms() {
        val m = model(MapStore())
        m.receivePairLink("aeon://pair?u=http%3A%2F%2F127.0.0.1%3A9&c=75KFXQWOXQGQ7VLQ")
        assertEquals(SetupStep.Terms, m.setup.step, "the agreement still comes first, but the short-lived code is not left to expire in the intro")
        assertEquals("75KFXQWOXQGQ7VLQ", m.state.value.pairPrefill?.code)
        waitFor { m.state.value.pairPrefill?.probing == false }
        assertEquals(null, m.state.value.pairPrefill?.address, "nothing answers there, so no address is offered as found")
        assertTrue(m.state.value.connection is dev.aeon.ui.state.Connection.Unpaired, "a link never pairs by itself")

        m.receivePairLink("aeon://pair?c=nope")
        assertEquals("읽을 수 없는 QR 코드예요.", m.state.value.message)
        assertEquals("75KFXQWOXQGQ7VLQ", m.state.value.pairPrefill?.code, "a bad link does not replace a good one")
    }

    @Test
    fun aLinkIsRefusedOnADeviceThatIsAlreadyConnected() {
        val m = model(MapStore(mutableMapOf("serverUrl" to "http://127.0.0.1:9", "deviceId" to "dev_0123456789abcdef01")))
        m.receivePairLink("aeon://pair?c=75KFXQWOXQGQ7VLQ")
        assertEquals(null, m.state.value.pairPrefill)
        assertTrue(m.state.value.message!!.contains("이미 컴퓨터에 연결"))
    }

    @Test
    fun consentsRoundTripAndRejectGarbage() {
        val c = Consents(service = true, data = true, online = false, background = true, acceptedAt = 1794456211444)
        assertEquals(c, Consents.decode(c.encode()))
        assertEquals(Consents(), Consents.decode(null))
        assertEquals(Consents(), Consents.decode("yes"))
        assertEquals(Consents(), Consents.decode("11x1:5"))
    }
}
