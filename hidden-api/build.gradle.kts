// Compile-only declarations of Android system APIs the shell helper may use but the public SDK
// omits. Never packaged: the real classes come from the device framework at runtime.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
