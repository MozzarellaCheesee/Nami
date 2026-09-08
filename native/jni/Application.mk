# libopenmpt требует исключения и RTTI (её собственный Application.mk из build/android_ndk
# выставляет ровно это), поэтому STL берём shared - две библиотеки в одном APK не должны
# тащить по своей копии libc++.
APP_STL      := c++_shared
APP_CPPFLAGS := -fexceptions -frtti
APP_PLATFORM := android-26
# 16 КБ страницы - требование Google Play с ноября 2025, иначе .so не грузится на новых
# устройствах.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
