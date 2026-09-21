package dev.primeremote.app

import android.app.Application

class PrimeRemoteApp : Application() {

    /** Created lazily so unit tests and tooling can instantiate the app cheaply. */
    val controller: AppController by lazy { AppController(this) }
}
