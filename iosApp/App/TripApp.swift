import ActivityKit
import BackgroundTasks
import Shared
import SwiftUI
import WidgetKit

@main
struct TripApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
        .onChange(of: phase) { _, p in
            if p == .active { IosApp.shared.foreground() }
            if p == .background { AppDelegate.scheduleRefresh() }
        }
    }
}

/** The whole app UI, drawn by Compose from the shared Kotlin code. */
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainKt.MainViewController() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
}

class AppDelegate: NSObject, UIApplicationDelegate {
    static let refreshTask = "app.trip.refresh"

    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        IosApp.shared.start(host: Host())
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTask, using: nil) { task in
            Self.scheduleRefresh()
            task.expirationHandler = { task.setTaskCompleted(success: false) }
            IosApp.shared.background { task.setTaskCompleted(success: true) }
        }
        return true
    }

    /// Asks iOS for a background refresh in 15 minutes or later, as often as it allows: the closest this gets to Android's alarm chain.
    static func scheduleRefresh() {
        let r = BGAppRefreshTaskRequest(identifier: refreshTask)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(r)
    }
}

/** ActivityKit and WidgetKit for the Kotlin side, which can't reach them itself. */
final class Host: NSObject, IosHost {
    func live(state: String?) {
        Task { @MainActor in
            guard let json = state, let s = TripActivity.ContentState.decode(json) else {
                for a in Activity<TripActivity>.activities { await a.end(nil, dismissalPolicy: .immediate) }
                return
            }
            let content = ActivityContent(state: s, staleDate: s.nextAt)
            if let a = Activity<TripActivity>.activities.first {
                await a.update(content)
            } else if ActivityAuthorizationInfo().areActivitiesEnabled {
                _ = try? Activity.request(attributes: TripActivity(), content: content)
            }
        }
    }

    func reloadWidgets() { WidgetCenter.shared.reloadAllTimelines() }
}
