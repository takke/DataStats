@echo off

::: clean, APK
call gradlew clean ^
 :app:publishAll

pause
