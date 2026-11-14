// Starts Node.js (nodejs-mobile) inside the app's process. Node can be started once per process and blocks until it
// exits, so the caller runs this on its own thread. Everything Node prints goes to logcat under the tag "AeonNode".
#include <jni.h>
#include <dlfcn.h>
#include <signal.h>
#include <unwind.h>
#include <sys/syscall.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include "node.h"

// If Node ends its process with exit() or crashes natively, write where from (library, symbol) to logcat first.
struct BtState { int n; };
static _Unwind_Reason_Code unwindStep(struct _Unwind_Context* ctx, void* arg) {
  auto* st = static_cast<BtState*>(arg);
  uintptr_t pc = _Unwind_GetIP(ctx);
  if (pc == 0) return _URC_NO_REASON;
  Dl_info info;
  const char* lib = "?";
  const char* sym = "?";
  uintptr_t off = 0;
  if (dladdr(reinterpret_cast<void*>(pc), &info)) {
    if (info.dli_fname) lib = info.dli_fname;
    if (info.dli_sname) sym = info.dli_sname;
    off = pc - reinterpret_cast<uintptr_t>(info.dli_fbase);
  }
  __android_log_print(ANDROID_LOG_ERROR, "AeonNode", "  #%02d pc %p %s (%s) +0x%zx", st->n, reinterpret_cast<void*>(pc), lib, sym, off);
  return ++st->n >= 30 ? _URC_END_OF_STACK : _URC_NO_REASON;
}
static void logBacktrace(const char* why) {
  __android_log_print(ANDROID_LOG_ERROR, "AeonNode", "%s (tid %ld)", why, (long)syscall(SYS_gettid));
  BtState st{0};
  _Unwind_Backtrace(unwindStep, &st);
}
static void onExitCalled() { logBacktrace("exit() was called"); }
static void onFatalSignal(int sig, siginfo_t* si, void*) {
  __android_log_print(ANDROID_LOG_ERROR, "AeonNode", "fatal signal %d, fault address %p", sig, si ? si->si_addr : nullptr);
  logBacktrace("native crash");
  signal(sig, SIG_DFL);
  if (sig == SIGABRT) raise(sig);  // for SEGV, returning runs the faulting instruction again and the system takes over
}
static void installDiagnostics() {
  atexit(onExitCalled);
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = onFatalSignal;
  sa.sa_flags = SA_SIGINFO;
  sigemptyset(&sa.sa_mask);
  sigaction(SIGSEGV, &sa, nullptr);
  sigaction(SIGABRT, &sa, nullptr);
  sigaction(SIGBUS, &sa, nullptr);
}

static int pipeFd[2];
static pthread_t logThread;

static void* pumpToLogcat(void*) {
  char buf[1024];
  ssize_t n;
  while ((n = read(pipeFd[0], buf, sizeof(buf) - 1)) > 0) {
    if (buf[n - 1] == '\n') --n;
    buf[n] = 0;
    __android_log_write(ANDROID_LOG_INFO, "AeonNode", buf);
  }
  return nullptr;
}

static void redirectOutputToLogcat() {
  setvbuf(stdout, nullptr, _IONBF, 0);
  setvbuf(stderr, nullptr, _IONBF, 0);
  if (pipe(pipeFd) != 0) return;
  dup2(pipeFd[1], STDOUT_FILENO);
  dup2(pipeFd[1], STDERR_FILENO);
  if (pthread_create(&logThread, nullptr, pumpToLogcat, nullptr) == 0) pthread_detach(logThread);
}

extern "C" JNIEXPORT jint JNICALL Java_dev_aeon_ui_platform_NodeHost_start(JNIEnv* env, jobject, jobjectArray arguments, jstring tmpDir) {
  const char* tmp = env->GetStringUTFChars(tmpDir, nullptr);
  setenv("TMPDIR", tmp, 1);
  setenv("HOME", tmp, 1);
  env->ReleaseStringUTFChars(tmpDir, tmp);

  // Node wants the arguments in one contiguous block (libuv rewrites the process title in place).
  jsize argc = env->GetArrayLength(arguments);
  std::vector<std::string> items;
  size_t total = 0;
  for (jsize i = 0; i < argc; i++) {
    jstring s = (jstring)env->GetObjectArrayElement(arguments, i);
    const char* c = env->GetStringUTFChars(s, nullptr);
    items.emplace_back(c);
    env->ReleaseStringUTFChars(s, c);
    env->DeleteLocalRef(s);
    total += items.back().size() + 1;
  }
  char* block = (char*)calloc(total + 1, 1);
  std::vector<char*> argv(argc + 1, nullptr);
  char* cursor = block;
  for (jsize i = 0; i < argc; i++) {
    memcpy(cursor, items[i].c_str(), items[i].size() + 1);
    argv[i] = cursor;
    cursor += items[i].size() + 1;
  }
  redirectOutputToLogcat();
  installDiagnostics();
  __android_log_print(ANDROID_LOG_INFO, "AeonNode", "calling node::Start");
  return jint(node::Start(argc, argv.data()));
}
