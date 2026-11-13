# Node's libnode.so needs the shared C++ runtime, which Gradle then packages next to it.
APP_STL := c++_shared
APP_PLATFORM := android-26
APP_ABIS := arm64-v8a x86_64
