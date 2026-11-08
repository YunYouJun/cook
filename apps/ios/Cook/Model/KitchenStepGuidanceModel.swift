import CookCore
import Foundation
import Observation

/// In-memory only. Requests and answers disappear with the workbench context.
@MainActor
@Observable
final class KitchenStepGuidanceModel {
    enum Phase: Equatable {
        case idle
        case loading
        case answer
        case sourceDoesNotSay
        case error
    }

    typealias Perform = @MainActor (StepGuidancePrompt) async throws -> StepGuidanceResponse

    private(set) var phase: Phase = .idle
    private(set) var prompt: StepGuidancePrompt?
    private(set) var response: StepGuidanceResponse?
    private(set) var message: String?
    @ObservationIgnored private var request: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()

    var isLoading: Bool { phase == .loading }

    func applies(to stepID: String) -> Bool {
        prompt?.request.stepID == stepID
    }

    func submit(_ prompt: StepGuidancePrompt, perform: @escaping Perform) {
        cancel()
        let id = UUID()
        generation = id
        self.prompt = prompt
        response = nil
        message = nil
        phase = .loading
        request = Task { @MainActor in
            defer {
                if generation == id {
                    request = nil
                    if phase == .loading { phase = .idle }
                }
            }
            do {
                let result = try await perform(prompt)
                try Task.checkCancellation()
                guard generation == id, self.prompt?.request == prompt.request else { return }
                let checked = try StepGuidanceValidator.validate(result, for: prompt)
                response = checked
                phase = checked.status == .sourceDoesNotSay ? .sourceDoesNotSay : .answer
            } catch is CancellationError {
                return
            } catch let error as StepGuidanceError {
                guard !Task.isCancelled, generation == id else { return }
                message = error.localizedDescription
                phase = .error
            } catch {
                guard !Task.isCancelled, generation == id else { return }
                message = StepGuidanceError.unavailable.localizedDescription
                phase = .error
            }
        }
    }

    func fail(_ error: StepGuidanceError) {
        cancel()
        prompt = nil
        response = nil
        message = error.localizedDescription
        phase = .error
    }

    func cancel() {
        generation = UUID()
        request?.cancel()
        request = nil
        if phase == .loading { phase = .idle }
    }

    func invalidate() {
        cancel()
        prompt = nil
        response = nil
        message = nil
        phase = .idle
    }
}
