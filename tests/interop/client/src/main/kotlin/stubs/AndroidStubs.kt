// Minimal JVM stand-ins for Android framework / app classes that SyncClient and SyncHub reference.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.util

object Log {
    @JvmStatic fun d(tag: String, msg: String) = println("D/$tag: $msg").let { 0 }
    @JvmStatic fun i(tag: String, msg: String) = println("I/$tag: $msg").let { 0 }
    @JvmStatic fun w(tag: String, msg: String) = println("W/$tag: $msg").let { 0 }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable) = println("W/$tag: $msg $tr").let { 0 }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable) = println("E/$tag: $msg $tr").let { 0 }
}
