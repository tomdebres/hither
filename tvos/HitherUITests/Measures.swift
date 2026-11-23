import XCTest

/// What Tom saw on M8 part 2's screenshots, measured on the screen: a focused field that grew out of its column with a
/// placeholder that barely read on it, and a state's words sitting on its buttons.
extension HitherUITestCase {
    /// A focused field sits in its column as the field under it does: the same left and right edges and the same height
    /// on the screen, to 2 points (the system's focused field was 8 points wider and 9 taller than the one under it, its
    /// left edge past the column); and what it holds, its placeholder when empty, reads on it at 4.5:1 (the grey
    /// placeholder on the system's near-white was about 2:1).
    func assertFocusedFieldInColumn(_ id: String, beside neighbour: String, file: StaticString = #filePath, line: UInt = #line) {
        waitForFocus(id, "\(id) focused", file: file, line: line)
        // The focus animation settled.
        Thread.sleep(forTimeInterval: 1)
        let shot = XCUIScreen.main.screenshot()
        guard let focused = capsule(around: element(id).frame, in: shot),
              let rest = capsule(around: element(neighbour).frame, in: shot) else {
            return XCTFail("\(id)'s and \(neighbour)'s capsules not found on the screen", file: file, line: line)
        }
        // Every problem at once (the test stops at its first failure): the frame and the words.
        var problems: [String] = []
        let both = "focused \(id) \(focused), \(neighbour) \(rest)"
        if abs(focused.minX - rest.minX) > 2 { problems.append("not the same left edge: \(both)") }
        if abs(focused.maxX - rest.maxX) > 2 { problems.append("not the same right edge: \(both)") }
        if abs(focused.height - rest.height) > 2 { problems.append("not the same height: \(both)") }
        let ground = CGRect(x: (focused.maxX - 40).rounded(), y: focused.midY.rounded(), width: 1, height: 1)
        let words = CGRect(x: (focused.minX + 16).rounded(), y: (focused.midY - 14).rounded(), width: 320, height: 28)
        guard let platter = colours(in: ground, of: shot)?.rgb(0, 0), let ink = colours(in: words, of: shot) else {
            return XCTFail("\(id)'s words not read off the screen", file: file, line: line)
        }
        var best = 1.0
        for y in 0..<ink.height {
            for x in 0..<ink.width { best = max(best, Self.contrast(ink.rgb(x, y), platter)) }
        }
        if best < 4.5 { problems.append("\(id)'s words \(String(format: "%.1f", best)):1 on its focused capsule \(platter), under 4.5:1") }
        // Each one surface: no capsule inside the capsule (the system's own field drawn inside Hither's showed as a ring).
        for (name, capsule) in [(id, focused), (neighbour, rest)] {
            if let ring = ring(in: capsule, of: shot) { problems.append("\(name) is not one surface: \(ring)") }
        }
        XCTAssertTrue(problems.isEmpty, problems.joined(separator: "; "), file: file, line: line)
    }

    /// A column's fields at rest alike on the screen once something was typed: the same left and right edges and the
    /// same height as the first, to 2 points. tvOS's keyboard hands focus back to the field it opened on as it closes,
    /// and that field came out of it 82 points high, its neighbours 70 (the first field of every form, M8 part 2).
    func assertFieldsAlike(_ ids: [String], file: StaticString = #filePath, line: UInt = #line) {
        let shot = XCUIScreen.main.screenshot()
        let found = ids.map { (id: $0, capsule: capsule(around: element($0).frame, in: shot)) }
        guard let first = found.first?.capsule else { return XCTFail("\(ids[0])'s capsule not found on the screen", file: file, line: line) }
        var problems: [String] = []
        for (id, capsule) in found.dropFirst() {
            guard let capsule else {
                problems.append("\(id)'s capsule not found")
                continue
            }
            if abs(capsule.minX - first.minX) > 2 || abs(capsule.maxX - first.maxX) > 2 || abs(capsule.height - first.height) > 2 {
                problems.append("\(ids[0]) \(first), \(id) \(capsule)")
            }
        }
        XCTAssertTrue(problems.isEmpty, "fields not alike at rest: " + problems.joined(separator: "; "), file: file, line: line)
    }

    /// Nothing but its own words on a field at rest: no faint second copy of them. The system's field sits under Hither's
    /// drawing at 2%; its words, larger, showed through as dim glyphs beside Hither's until they were made clear (`#252525`
    /// on the capsule's `#212121`). Dim: lighter than the capsule by 3 to 70, more than 2 points from bright ink (whose
    /// edges are dim too).
    func assertNoGhostWords(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        let shot = XCUIScreen.main.screenshot()
        guard let field = capsule(around: element(id).frame, in: shot),
              let ground = colours(in: CGRect(x: (field.maxX - 40).rounded(), y: field.midY.rounded(), width: 1, height: 1), of: shot)?.rgb(0, 0),
              let band = colours(in: CGRect(x: (field.minX + 24).rounded(), y: (field.midY - 16).rounded(), width: (field.width - 60).rounded(), height: 32), of: shot)
        else { return XCTFail("\(id) not read off the screen", file: file, line: line) }
        let lift = { (c: (Int, Int, Int)) in max(c.0, c.1, c.2) - max(ground.0, ground.1, ground.2) }
        var bright = Set<Int>()
        for y in 0..<band.height { for x in 0..<band.width where lift(band.rgb(x, y)) > 70 { bright.insert(y * band.width + x) } }
        let nearBright = { (x: Int, y: Int) in
            for dy in -2...2 { for dx in -2...2 where bright.contains((y + dy) * band.width + x + dx) { return true } }
            return false
        }
        var dim = 0
        for y in 0..<band.height {
            for x in 0..<band.width {
                let l = lift(band.rgb(x, y))
                if l >= 3, l <= 70, !nearBright(x, y) { dim += 1 }
            }
        }
        XCTAssertLessThanOrEqual(dim, 20, "\(id): \(dim) dim points on its capsule \(ground), a second copy of its words", file: file, line: line)
    }

    /// A step of colour along [capsule]'s row 10 points under its top, between its rounded ends (above a field's words):
    /// nil when it is one colour there, to 8 in red, green or blue.
    func ring(in capsule: CGRect, of shot: XCUIScreenshot) -> String? {
        let row = CGRect(x: (capsule.minX + 36).rounded(), y: (capsule.minY + 10).rounded(), width: (capsule.width - 72).rounded(), height: 1)
        guard let colours = colours(in: row, of: shot) else { return "not read" }
        let first = colours.rgb(0, 0)
        for x in 0..<colours.width {
            let c = colours.rgb(x, 0)
            if max(abs(c.0 - first.0), abs(c.1 - first.1), abs(c.2 - first.2)) > 8 {
                return "\(first) at its left, \(c) \(x + 36) points in"
            }
        }
        return nil
    }

    /// What a field's capsule covers on [shot]: along the screen's row through [frame]'s middle, from the first to the last
    /// point lighter than the black ground within 60 points of it; down the column 40 points in from that row's right
    /// end, the lit run through the middle.
    func capsule(around frame: CGRect, in shot: XCUIScreenshot) -> CGRect? {
        let across = CGRect(x: (frame.minX - 60).rounded(), y: frame.midY.rounded(), width: (frame.width + 120).rounded(), height: 1)
        guard let row = colours(in: across, of: shot) else { return nil }
        let lit = (0..<row.width).filter { Self.lit(row.rgb($0, 0)) }
        guard let first = lit.first, let last = lit.last else { return nil }
        let left = across.minX + CGFloat(first), right = across.minX + CGFloat(last + 1)
        let down = CGRect(x: (right - 40).rounded(), y: (frame.minY - 40).rounded(), width: 1, height: (frame.height + 80).rounded())
        guard let column = colours(in: down, of: shot) else { return nil }
        let middle = Int((frame.midY - down.minY).rounded())
        guard middle >= 0, middle < column.height, Self.lit(column.rgb(0, middle)) else { return nil }
        var top = middle, bottom = middle
        while top > 0, Self.lit(column.rgb(0, top - 1)) { top -= 1 }
        while bottom < column.height - 1, Self.lit(column.rgb(0, bottom + 1)) { bottom += 1 }
        return CGRect(x: left, y: down.minY + CGFloat(top), width: right - left, height: CGFloat(bottom - top + 1))
    }

    /// Room between a `StateView`'s words and its buttons, as the system spaces its actions from a description: at least
    /// [room] points from the foot of its last line to the top of its buttons (Quick Connect's failure had them 6
    /// points apart on the screen, its frames none).
    func assertActionsRoom(_ id: String, room: CGFloat = 24, file: StaticString = #filePath, line: UInt = #line) {
        let state = element(id)
        let words = state.staticTexts.allElementsBoundByIndex.map(\.frame).filter { !$0.isEmpty }
        let actions = state.buttons.allElementsBoundByIndex.map(\.frame).filter { !$0.isEmpty }
        guard let foot = words.map(\.maxY).max(), let top = actions.map(\.minY).min() else {
            return XCTFail("\(id): words \(words), buttons \(actions)", file: file, line: line)
        }
        XCTAssertGreaterThanOrEqual(top - foot, room, "\(id): its buttons \(top - foot) points under its words (\(words), \(actions))", file: file, line: line)
    }

    private static func lit(_ colour: (Int, Int, Int)) -> Bool { max(colour.0, colour.1, colour.2) > 12 }

    /// WCAG's contrast ratio of two colours (1-21).
    static func contrast(_ a: (Int, Int, Int), _ b: (Int, Int, Int)) -> Double {
        func luminance(_ c: (Int, Int, Int)) -> Double {
            func linear(_ v: Int) -> Double {
                let s = Double(v) / 255
                return s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * linear(c.0) + 0.7152 * linear(c.1) + 0.0722 * linear(c.2)
        }
        let (x, y) = (luminance(a), luminance(b))
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }
}
