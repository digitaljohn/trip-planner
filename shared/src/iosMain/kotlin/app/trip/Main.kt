package app.trip

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** The app's screens, for the Swift app to host. */
fun MainViewController(): UIViewController = ComposeUIViewController { TripTheme { App() } }
