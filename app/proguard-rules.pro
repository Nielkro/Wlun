-repackageclasses ''
-allowaccessmodification

-keep class app.qthrone.mobile.** { *;}
-keep class moe.matsuri.nb4a.** { *;}
# OutboundFactory instantiates the profile types reflectively by class name
-keep class app.qthrone.mobile.outbound.types.** { *; }

# Clean Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNull(java.lang.Object);
    static void checkNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void throwUninitializedPropertyAccessException(java.lang.String);
}

# SnakeYaml
-keep class org.yaml.snakeyaml.** { *; }

# JSch's algorithm registry instantiates implementations by class name.
# This includes jce.Random before the first SSH connection is opened.
-keep class !com.jcraft.jsch.PageantConnector,!com.jcraft.jsch.JUnixSocketFactory,!com.jcraft.jsch.Log4j2Logger,!com.jcraft.jsch.Slf4jLogger,!com.jcraft.jsch.jgss.**,com.jcraft.jsch.** { *; }
# Optional desktop agents, logging adapters and Kerberos are not used by VPS
# password/key authentication over TCP. Do not retain their implementations.
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**

-dontobfuscate
-keepattributes SourceFile

-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.Transient
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.bouncycastle.jce.provider.BouncyCastleProvider
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn java.beans.PropertyVetoException
