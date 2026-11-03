LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# Locus: 16 KB pages for Android 15+ devices (as tools/build-proot.sh), and Termux's gc-sections.
# No build ID: lld hashes the whole output into it, so it would differ wherever the rest does
# (F-Droid's Reproducible Builds page recommends --build-id=none for NDK code).
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--gc-sections -Wl,--build-id=none
include $(BUILD_SHARED_LIBRARY)
