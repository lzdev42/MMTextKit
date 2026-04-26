package xyz.mmtextkit

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform