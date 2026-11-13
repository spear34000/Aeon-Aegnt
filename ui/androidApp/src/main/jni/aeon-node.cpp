// Starts Node.js (nodejs-mobile) inside the app's process. Node can be started once per process and blocks until it
// exits, so the caller runs this on its own thread. Everything Node prints goes to logcat under the tag "AeonNode".
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include "node.h"

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
  return jint(node::Start(argc, argv.data()));
}
