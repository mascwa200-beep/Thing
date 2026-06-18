// IPC surface for the isolated inference process. The model is single-shot (generateResponse
// returns the whole reply), so one blocking call per request is enough — the main process
// re-streams word-by-word. If the isolated process aborts (native model-load crash), these
// calls throw DeadObjectException in the caller, which keeps the main app alive.
package dev.mascwa.pulse.jarvis.inference;

interface IInferenceService {
    /** Load the model in this process. [backend]: 0=auto/default, 1=GPU, 2=CPU. Returns true on
     *  success; throws if the process dies. */
    boolean load(String modelPath, int maxTokens, int backend);

    /** Run one full generation for an already-built prompt. */
    String generate(String fullPrompt);
}
