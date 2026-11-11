# AppMetrica 8.6.0's AppSetIdGetter checks for AppSet before selecting its Google Play implementation.
# The SDK keeps the optional implementation even when the Google Play API is excluded.
-dontwarn com.google.android.gms.appset.AppSet
-dontwarn com.google.android.gms.appset.AppSetIdClient
-dontwarn com.google.android.gms.appset.AppSetIdInfo
-dontwarn com.google.android.gms.tasks.OnCompleteListener
-dontwarn com.google.android.gms.tasks.Task
