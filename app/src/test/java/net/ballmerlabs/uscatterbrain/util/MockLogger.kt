package net.ballmerlabs.uscatterbrain.util

val mockLoggerGenerator = { c: Class<*> -> MockLogger(c) }

class MockLogger(c: Class<*>) : Logger(c) {
    override fun d(text: String) {
        println("$name: $text")
    }

    override fun w(text: String) {
        println("$name: $text")
    }

    override fun v(text: String) {
        println("$name: $text")
    }

    override fun e(text: String) {
        println("$name: $text")
    }

    override fun i(text: String) {
        println("$name: $text")
    }

    override fun cry(text: String) {
        println("$name: $text")
    }
}