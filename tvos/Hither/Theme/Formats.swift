import Foundation
import HitherCore

/// How Hither writes a value on the TV (the review's X9 and F29): one way for each kind, in the device's language, so a
/// date never reads two ways on one screen. Before this, a film's release read "16 September 2026" (`dateStyle .long`
/// follows the region) on the same TV where a request read "Jan 1, 2026" (a template follows the language), a runtime
/// "94 minutes", and a person's facts were split by a bar where every other line uses the middle dot.
///
/// Values come from `core` as it gives them (ISO dates, minutes, whole dollars) and are written here, in Swift, so
/// Android and `core` are untouched.
enum Formats {
    /// Between facts on one line ("2026 · 1 hr 34 min · Drama"), as the Apple TV app separates them.
    static let separator = " · "

    /// The parts that are there, on one line between middle dots; nil when none is.
    static func join(_ parts: [String?]) -> String? {
        let shown = parts.compactMap { $0 }.filter { !$0.isEmpty }
        return shown.isEmpty ? nil : shown.joined(separator: separator)
    }

    // MARK: - Dates

    /// A day: "Sep 16, 2026" (`Date.FormatStyle`'s abbreviated date), in the device's language and order.
    static func date(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .omitted)
    }

    /// A day the server writes `yyyy-MM-dd` (TMDB's release and air dates, a birthday), as `date`; nil if it is not one.
    static func day(_ iso: String) -> String? {
        dayParser.date(from: iso).map(date)
    }

    /// A moment with its time, for Diagnostics: "Nov 15, 2026 at 18:43:27".
    static func dateTime(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .standard)
    }

    /// "3 days ago" within a week, the day beyond it (`date`), for the server's ISO time; nil if it is not one. Android's
    /// `relativeTime` (DateUtils: relative within a week; DECISIONS 66, 200). A time the server has ahead of the TV's
    /// clock reads as now.
    static func relative(_ iso: String?, now: Date = Date()) -> String? {
        guard let iso, let then = ISO8601DateFormatter.lenient(iso).map({ min($0, now) }) else { return nil }
        // RelativeDateTimeFormatter alone says "10 months ago" or "last week".
        if now.timeIntervalSince(then) >= 7 * 24 * 60 * 60 { return date(then) }
        let format = RelativeDateTimeFormatter()
        format.dateTimeStyle = .named
        return format.localizedString(for: then, relativeTo: now)
    }

    /// When something due is expected, from now: "in 42 minutes" (a download's estimate); nil if [iso] is not a time.
    static func due(_ iso: String?, now: Date = Date()) -> String? {
        guard let iso, let then = ISO8601DateFormatter.lenient(iso) else { return nil }
        return RelativeDateTimeFormatter().localizedString(for: then, relativeTo: now)
    }

    private static let dayParser: DateFormatter = {
        let parse = DateFormatter()
        parse.locale = Locale(identifier: "en_US_POSIX")
        parse.dateFormat = "yyyy-MM-dd"
        return parse
    }()

    // MARK: - Runtime and money

    /// A running time as the Apple TV app writes it: "1 hr 34 min", "45 min", "2 hr" (each unit in the device's
    /// language, joined by a space: `Duration`'s own style puts a comma between them). Nil for none.
    static func runtime(minutes: Int) -> String? {
        guard minutes > 0 else { return nil }
        let hours = minutes / 60, rest = minutes % 60
        let style = Measurement<UnitDuration>.FormatStyle(width: .abbreviated, usage: .asProvided)
        let parts = [
            hours > 0 ? Measurement(value: Double(hours), unit: UnitDuration.hours).formatted(style) : nil,
            rest > 0 ? Measurement(value: Double(rest), unit: UnitDuration.minutes).formatted(style) : nil,
        ]
        return parts.compactMap { $0 }.joined(separator: " ")
    }

    /// Whole US dollars, as Seerr's web writes a film's takings and budget: "$400,290,973" (no pence: polish part 1,
    /// DECISIONS 230).
    static func money(_ amount: Int64) -> String {
        let format = NumberFormatter()
        format.numberStyle = .currency
        format.locale = Locale(identifier: "en_US")
        format.currencyCode = "USD"
        format.maximumFractionDigits = 0
        return format.string(from: NSNumber(value: amount)) ?? "$\(amount)"
    }
}

/// Seerr's DownloadBlock's time left under a running download: "Estimated in 42 minutes", relative to now (the title
/// page's status line and a request's lockup).
func downloadEstimate(_ progress: DownloadProgress, now: Date) -> String? {
    Formats.due(progress.estimatedAt, now: now).map(Words.estimated)
}

extension ISO8601DateFormatter {
    /// An ISO 8601 instant with or without fractional seconds.
    static func lenient(_ text: String) -> Date? {
        let format = ISO8601DateFormatter()
        format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = format.date(from: text) { return date }
        format.formatOptions = [.withInternetDateTime]
        return format.date(from: text)
    }
}
