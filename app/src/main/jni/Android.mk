LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := mk15serial
LOCAL_SRC_FILES := mk15serial.c
LOCAL_CFLAGS := -Wall -Wextra -O2
include $(BUILD_SHARED_LIBRARY)
