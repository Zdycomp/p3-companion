plugins { kotlin("jvm") version "2.1.21" }
repositories { mavenCentral() }
val androidAll = "org.robolectric:android-all:15-robolectric-14663667-2"
dependencies {
    compileOnly(androidAll)
    testImplementation(androidAll)
    testImplementation("junit:junit:4.13.2")
}
kotlin { jvmToolchain(21) }
sourceSets {
    main { kotlin.srcDir("../app/src/main/java") }
    test { kotlin.srcDir("../app/src/test/java"); resources.srcDir("../app/src/test/resources") }
}
