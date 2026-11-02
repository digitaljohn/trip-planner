import ActivityKit
import SwiftUI
import WidgetKit

@main
struct TripWidgets: WidgetBundle {
    var body: some Widget {
        NowWidget()
        TripLiveActivity()
    }
}

// MARK: - Home-screen widget

/// One moment of the widget's timeline, written ahead by the app (IosAlerts.widgetTimeline).
struct NowEntry: TimelineEntry, Decodable {
    let date: Date
    let leg: String
    let title: String
    let detail: String?
    let next: String?
    let nextAt: Date?

    enum CodingKeys: String, CodingKey { case date = "at", leg, title, detail, next, nextAt }
}

struct NowProvider: TimelineProvider {
    static let sample = NowEntry(date: .now, leg: "AT THE AIRPORT · T−1:20", title: "Security", detail: nil, next: "Next · Gate shows on screens", nextAt: nil)
    static let empty = NowEntry(date: .now, leg: "TRIP", title: "Open the app once", detail: "The widget fills in from the app.", next: nil, nextAt: nil)

    func placeholder(in context: Context) -> NowEntry { Self.sample }

    func getSnapshot(in context: Context, completion: @escaping (NowEntry) -> Void) {
        completion(context.isPreview ? Self.sample : current(entries()) ?? Self.sample)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NowEntry>) -> Void) {
        let all = entries()
        let list = (current(all).map { [$0] } ?? []) + all.filter { $0.date > .now }
        completion(Timeline(entries: list.isEmpty ? [Self.empty] : list, policy: .atEnd))
    }

    private func current(_ all: [NowEntry]) -> NowEntry? { all.last { $0.date <= .now } }

    private func entries() -> [NowEntry] {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "TripAppGroup") as? String,
              let json = UserDefaults(suiteName: group)?.string(forKey: "widget.entries") else { return [] }
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return (try? d.decode([NowEntry].self, from: Data(json.utf8))) ?? []
    }
}

/// The leg, what to do now and a countdown to the next step, laid out as the Android widget.
struct NowView: View {
    let e: NowEntry
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(e.leg)
                .font(.system(size: 11, weight: .bold)).tracking(0.9)
                .foregroundStyle(Palette.accent(scheme)).lineLimit(1)
            Text(e.title)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Palette.fg(scheme)).lineLimit(1).padding(.top, 4)
            if let d = e.detail, !d.isEmpty {
                Text(d).font(.system(size: 13)).foregroundStyle(Palette.muted(scheme)).lineLimit(2).padding(.top, 2)
            }
            if let next = e.next {
                HStack(spacing: 8) {
                    Text(next).font(.system(size: 13)).foregroundStyle(Palette.fg(scheme)).lineLimit(1)
                    Spacer(minLength: 0)
                    if let at = e.nextAt, at > e.date {
                        (Text("in ") + Text(timerInterval: e.date...at, countsDown: true))
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(Palette.accent(scheme))
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 90, alignment: .trailing)
                    }
                }
                .padding(.top, 8)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .containerBackground(Palette.bg(scheme), for: .widget)
    }
}

struct NowWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Now", provider: NowProvider()) { NowView(e: $0) }
            .configurationDisplayName("Now")
            .description("Where you are in the trip, what to do now, and the countdown to the next step")
            .supportedFamilies([.systemMedium])
    }
}

// MARK: - Live Activity

private func countdown(_ s: TripActivity.ContentState) -> Text? {
    guard let at = s.nextAt, at > .now else { return nil }
    return Text(timerInterval: Date.now...at, countsDown: true)
}

/// The travel day as one bar: red for flights, grey for the ground, and how far through it you are.
struct DayBar: View {
    let s: TripActivity.ContentState
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if let start = s.day.first?.from, let end = s.day.last?.to {
            let total = end.timeIntervalSince(start)
            VStack(spacing: 4) {
                GeometryReader { g in
                    HStack(spacing: 2) {
                        ForEach(s.day, id: \.self) { p in
                            RoundedRectangle(cornerRadius: 2)
                                .fill(p.air ? Palette.accent(scheme) : Palette.ground)
                                .frame(width: max(2, g.size.width * p.to.timeIntervalSince(p.from) / total - 2))
                        }
                    }
                }
                .frame(height: 6)
                progress(start, end)
            }
        } else if let from = s.legFrom, let to = s.legTo {
            progress(from, to)
        }
    }

    /// Ticks along by itself for the live state; the preview shows its own moment.
    @ViewBuilder private func progress(_ from: Date, _ to: Date) -> some View {
        if abs(s.at.timeIntervalSinceNow) < 120 {
            ProgressView(timerInterval: from...to, countsDown: false, label: { EmptyView() }, currentValueLabel: { EmptyView() })
                .tint(Palette.fg(scheme))
        } else {
            ProgressView(value: min(1, max(0, s.at.timeIntervalSince(from) / to.timeIntervalSince(from))))
                .tint(Palette.fg(scheme))
        }
    }
}

struct LockView: View {
    let s: TripActivity.ContentState
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Circle().fill(Palette.accent(scheme)).frame(width: 7, height: 7)
                Text(s.leg.uppercased()).font(.system(size: 11, weight: .bold)).tracking(0.9).lineLimit(1)
                Spacer(minLength: 4)
                Text(s.chip).font(.system(size: 13, weight: .bold, design: .monospaced)).foregroundStyle(Palette.accent(scheme))
            }
            Text(s.title).font(.headline).lineLimit(1)
            let line = [s.place, s.detail.isEmpty ? nil : s.detail].compactMap { $0 }.joined(separator: " · ")
            if !line.isEmpty { Text(line).font(.subheadline).foregroundStyle(.secondary).lineLimit(2) }
            if let next = s.next {
                HStack {
                    Text(next).font(.footnote).lineLimit(1)
                    Spacer(minLength: 4)
                    countdown(s)?.font(.system(size: 13, weight: .bold, design: .monospaced)).foregroundStyle(Palette.accent(scheme))
                        .multilineTextAlignment(.trailing).frame(maxWidth: 90, alignment: .trailing)
                }
            }
            DayBar(s: s)
        }
    }
}

struct TripLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TripActivity.self) { ctx in
            LockView(s: ctx.state).padding(16)
        } dynamicIsland: { ctx in
            let s = ctx.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Text(s.chip).font(.system(size: 15, weight: .bold, design: .monospaced)).foregroundStyle(Palette.accent(.dark))
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let p = s.place { Text(p).font(.system(size: 15, weight: .bold)) }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(s.title).font(.headline).lineLimit(1)
                        if let next = s.next {
                            HStack {
                                Text(next).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                                Spacer(minLength: 4)
                                countdown(s)?.font(.system(size: 13, weight: .bold, design: .monospaced))
                                    .multilineTextAlignment(.trailing).frame(maxWidth: 90, alignment: .trailing)
                            }
                        }
                        DayBar(s: s).environment(\.colorScheme, .dark)
                    }
                }
            } compactLeading: {
                Text(s.chip).font(.system(size: 13, weight: .bold, design: .monospaced)).foregroundStyle(Palette.accent(.dark))
            } compactTrailing: {
                if let p = s.place {
                    Text(p).font(.system(size: 13, weight: .bold))
                } else {
                    countdown(s)?.font(.system(size: 13, weight: .bold, design: .monospaced)).frame(maxWidth: 56)
                }
            } minimal: {
                Circle().fill(Palette.accent(.dark)).frame(width: 8, height: 8)
            }
        }
    }
}
