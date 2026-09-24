package com.rife.androidtv

object NativeEngine {
    init {
        System.loadLibrary("rife_native")
    }

    @JvmStatic
    external fun runDiagnostics(): NativeDiagnosticResult
}
