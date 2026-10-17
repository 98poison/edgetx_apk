// Root build script: only declares plugin versions for all modules.
plugins {
    id("com.android.application") version "9.3.2" apply false

    // RcBridge.kt / Proto.kt: the DJI-SDK side of the bridge is Kotlin, and AGP 9
    // compiles Kotlin sources in the module on its own.
    //
    // Applying org.jetbrains.kotlin.android here as well used to work and then
    // stopped, and the reason is worth keeping in mind: AGP 9 registers a
    // `kotlin` extension of its own, so the standalone plugin's
    // createKotlinExtension() finds the name already taken and the build dies
    // with "Cannot add extension with name 'kotlin'". It only appeared after the
    // configuration cache was cleared, because that cache had been hiding the
    // conflict all along - so it is a latent bug, not a new one.
    // See app/build.gradle.kts for the stdlib that goes with this.
}
