package ru.privetdns.zapret
object NativeBridge {
    init { System.loadLibrary("byedpi") }
    external fun startProxy(args: Array<String>): Int
    external fun stopProxy(): Int
    external fun startTunnel(config: String, fd: Int): Int
    external fun stopTunnel()
}
