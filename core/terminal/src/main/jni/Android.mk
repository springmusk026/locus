LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# Locus: 16 KB pages for Android 15+ devices (as tools/build-proot.sh), and Termux's gc-sections.
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--gc-sections
include $(BUILD_SHARED_LIBRARY)
