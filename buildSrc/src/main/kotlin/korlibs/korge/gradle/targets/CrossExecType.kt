package korlibs.korge.gradle.targets

import org.gradle.configurationcache.extensions.*

object WineHQ {
    val EXEC = "wine64"
}

enum class CrossExecType(val cname: String, val interp: String) {
    WINDOWS("mingw", "wine"),
    LINUX("linux", "lima");

    val valid: Boolean get() = when (this) {
        WINDOWS -> !isWindows
        LINUX -> !korlibs.korge.gradle.targets.isLinux
    }

    val interpCapital = interp.capitalized()
    val nameWithArch = "${cname}X64"
    val nameWithArchCapital = nameWithArch.capitalized()

    fun commands(vararg args: String): Array<String> {
        return ArrayList<String>().apply {
            when (this@CrossExecType) {
                WINDOWS -> {
                    if (isArm && !isMacos) add("box64") // wine on macos can run x64 apps via rosetta, but linux needs box64 emulator
                    add(WineHQ.EXEC)
                }
                LINUX -> {
                    // @TODO: WSL
                    if (isWindows) add("wsl") else add("lima")
                    if (isArm) add("box64")
                }
            }
            addAll(args)
        }.toTypedArray()
    }

    companion object {
        val VALID_LIST: List<CrossExecType> = values().filter { it.valid }
    }
}