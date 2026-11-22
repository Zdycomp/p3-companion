plugins { kotlin("jvm") version "2.1.21" }
repositories { mavenCentral() }
val androidAll = "org.robolectric:android-all:15-robolectric-14663667-2"
dependencies {
    compileOnly(androidAll)
    testImplementation(androidAll)
    testImplementation("junit:junit:4.13.2")
}
kotlin { jvmToolchain(21) }

// The app's R class doesn't exist outside the Android build, so make a stub from the res folder.
// Every R.drawable / R.mipmap / R.xml / R.string the code uses must exist there, or this fails to compile.
val resDir = file("../app/src/main/res")
val rStubDir = layout.buildDirectory.dir("generated/rstub")
val rStub = tasks.register("rStub") {
    inputs.dir(resDir)
    outputs.dir(rStubDir)
    doLast {
        val types = sortedMapOf<String, MutableSet<String>>()
        resDir.listFiles()!!.filter { it.isDirectory }.forEach { dir ->
            val type = dir.name.substringBefore('-')
            if (type == "values") {
                dir.listFiles()!!.forEach { f ->
                    Regex("<(string|color|style|dimen|bool|integer)\\s+name=\"([^\"]+)\"").findAll(f.readText()).forEach { m ->
                        types.getOrPut(m.groupValues[1]) { sortedSetOf() } += m.groupValues[2].replace('.', '_')
                    }
                }
            } else {
                dir.listFiles()!!.forEach { f -> types.getOrPut(type) { sortedSetOf() } += f.name.substringBefore('.') }
            }
        }
        var id = 0x7f000000
        val text = buildString {
            appendLine("package com.zdycomp.p3companion")
            appendLine("@Suppress(\"ClassName\", \"unused\")")
            appendLine("object R {")
            types.forEach { (type, names) ->
                appendLine("    object $type {")
                names.forEach { appendLine("        const val $it = ${id++}") }
                appendLine("    }")
            }
            appendLine("}")
        }
        val out = rStubDir.get().asFile
        out.mkdirs()
        File(out, "R.kt").writeText(text)
    }
}
sourceSets {
    main { kotlin.srcDir("../app/src/main/java"); kotlin.srcDir(rStubDir) }
    test { kotlin.srcDir("../app/src/test/java"); resources.srcDir("../app/src/test/resources") }
}
tasks.named("compileKotlin") { dependsOn(rStub) }
