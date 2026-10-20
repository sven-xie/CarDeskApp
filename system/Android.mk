#
# Copyright (C) 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

LOCAL_PATH := $(call my-dir)

# CarDesk is a platform-signed privileged system app.  The APK is built
# from G:\workspace\MyCar\CarDeskApp and copied here before the AOSP build.
include $(CLEAR_VARS)
LOCAL_MODULE := MyCarDesk
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := MyCarDesk.apk
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true
LOCAL_MODULE_PATH := $(TARGET_OUT_APPS_PRIVILEGED)
LOCAL_OVERRIDES_PACKAGES := Launcher3 Launcher3QuickStep
LOCAL_DEX_PREOPT := false
include $(BUILD_PREBUILT)

# Privileged permission allow-list for the prebuilt CarDesk APK.
include $(CLEAR_VARS)
LOCAL_MODULE := MyCarDeskPrivappPermissions
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/permissions
LOCAL_MODULE_STEM := privapp-permissions-mycardesk.xml
LOCAL_SRC_FILES := privapp-permissions-mycardesk.xml
include $(BUILD_PREBUILT)

# Auto-grant the runtime location permissions used by the weather widgets.
include $(CLEAR_VARS)
LOCAL_MODULE := MyCarDeskDefaultPermissions
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/default-permissions
LOCAL_MODULE_STEM := default-permissions-mycardesk.xml
LOCAL_SRC_FILES := default-permissions-mycardesk.xml
include $(BUILD_PREBUILT)
