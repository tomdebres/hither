import SwiftUI

/// What went wrong, for the system's alert (Tom's D4, the review's F18: failures are alerts, not toasts): a short title
/// and the sentence that says why, in Seerr's or Android's words.
struct AlertText: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let message: String
}

extension View {
    /// Shows [problem] as the system's alert with one OK, once whatever was on screen when it went wrong (a sheet, a
    /// dialog, another alert) has had time to go: an alert asked for while another presentation is leaving is not shown.
    /// [onDone] runs as it closes; focus goes back where it was.
    func problemAlert(_ problem: AlertText?, onDone: @escaping () -> Void) -> some View {
        modifier(ProblemAlert(problem: problem, onDone: onDone))
    }
}

private struct ProblemAlert: ViewModifier {
    let problem: AlertText?
    let onDone: () -> Void
    @State private var shown: AlertText?

    /// Long enough for a sheet, a dialog or an alert to finish going (part 3's lesson: about 300 ms after a dialog).
    static let after: Duration = .milliseconds(600)

    func body(content: Content) -> some View {
        content
            .alert(
                shown?.title ?? "",
                isPresented: Binding(get: { shown != nil }, set: { if !$0 { shown = nil; onDone() } }),
                presenting: shown
            ) { _ in
                Button(Words.ok, role: .cancel) {}
                    .accessibilityIdentifier("problem ok")
            } message: { problem in
                Text(problem.message)
            }
            .task(id: problem?.id) {
                guard let problem else { return }
                try? await Task.sleep(for: Self.after)
                if !Task.isCancelled { shown = problem }
            }
    }
}
