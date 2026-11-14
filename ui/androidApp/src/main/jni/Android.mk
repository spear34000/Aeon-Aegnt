LOCAL_PATH := $(call my-dir)
NODE_ROOT := $(LOCAL_PATH)/../../../nodejs-mobile

# Node.js for Mobile (nodejs-mobile, Node 18), prebuilt for each ABI. Fetch it with tools/fetch-nodejs-mobile.mjs.
include $(CLEAR_VARS)
LOCAL_MODULE := node
LOCAL_SRC_FILES := $(NODE_ROOT)/bin/$(TARGET_ARCH_ABI)/libnode.so
LOCAL_EXPORT_C_INCLUDES := $(NODE_ROOT)/include/node
include $(PREBUILT_SHARED_LIBRARY)

# The small bridge that starts Node inside this process.
include $(CLEAR_VARS)
LOCAL_MODULE := aeonnode
LOCAL_SRC_FILES := aeon-node.cpp
LOCAL_C_INCLUDES := $(NODE_ROOT)/include/node
LOCAL_SHARED_LIBRARIES := node
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
