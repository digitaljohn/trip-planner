import ActivityKit
import SwiftUI

/// The lock-screen Live Activity. Its content is worked out in Kotlin (IosAlerts.liveState) and arrives as JSON.
struct TripActivity: ActivityAttributes {
    struct Part: Codable, Hashable {
        var from: Date
        var to: Date
        var air: Bool
    }

    struct ContentState: Codable, Hashable {
        /// The moment described: now, or a later one for the flight-day preview.
        var at: Date
        var title: String
        var detail: String
        var leg: String
        var chip: String
        /// "Gate B32" or "Belt 7", from live flight data.
        var place: String?
        var next: String?
        var nextAt: Date?
        var legFrom: Date?
        var legTo: Date?
        /// The travel day's legs, when now is inside one.
        var day: [Part]

        static func decode(_ json: String) -> ContentState? {
            let d = JSONDecoder()
            d.dateDecodingStrategy = .secondsSince1970
            return try? d.decode(ContentState.self, from: Data(json.utf8))
        }
    }
}

/// The app's palette (Theme.kt), for the parts drawn in SwiftUI.
enum Palette {
    static func accent(_ s: ColorScheme) -> Color { s == .dark ? Color(hex: 0xFF453C) : Color(hex: 0xC41F17) }
    static func fg(_ s: ColorScheme) -> Color { s == .dark ? Color(hex: 0xF6F6F3) : Color(hex: 0x0B0B0C) }
    static func muted(_ s: ColorScheme) -> Color { s == .dark ? Color(hex: 0xB4B4BA) : Color(hex: 0x6B6B72) }
    static func bg(_ s: ColorScheme) -> Color { s == .dark ? Color(hex: 0x161618) : Color(hex: 0xF6F6F3) }
    static let ground = Color(hex: 0xB4B4BA)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}
