# Free AI for the Previous Manual Record summary

The Summary of a Previous Manual Record is built in two layers:

1. **Calculation (always on, no AI).** `backend/src/sheetCompare.js` and `backend/src/manualAnalysis.js` read the saved cells and produce every figure: periods, indicator cards, bar-chart data, the comparison table, findings (each with its source cells) and data-quality notes.
2. **Wording (optional, free AI).** `backend/src/aiSummary.js` asks a self-hosted model to explain those findings in plain language. Its answer is accepted only if it has the required JSON shape, explains only findings that exist, and states only figures GoPreach calculated for that statement. Anything else is refused and the calculated wording is shown, with a plain message saying the AI did not produce it.

The AI is **off until you configure it**. Nothing paid is ever substituted.

## Model

| | |
|---|---|
| Recommended model | `qwen2.5:7b-instruct` (Alibaba Qwen2.5 7B Instruct) |
| Runtime | [Ollama](https://ollama.com) (MIT licence) |
| Model licence | Apache-2.0 for the 7B model (free for commercial use). **The 3B and 72B Qwen2.5 sizes use a different licence — do not swap to them without reading it.** |
| Download size | about 4.7 GB (4-bit quantised) |
| Memory | at least 8 GB RAM free for the model plus an 8K-token context; 16 GB is comfortable |
| Context | the model supports 32K tokens; GoPreach asks for 8,192 (`AI_CONTEXT_TOKENS`) and refuses to send a prompt over 24,000 characters |
| Structured output | Ollama's `format` accepts a JSON schema; GoPreach sends one and validates the answer again itself |

These figures are from the vendors' published information and were **not** re-measured on your server. Check the current licence and size on the model page before installing.

Any other model that follows a JSON schema well can be used by changing `AI_MODEL`. Any server that speaks `/v1/chat/completions` (llama.cpp, vLLM, LocalAI) works with `AI_PROVIDER=openai-compatible`.

## Where it must run

A model on a developer's computer is not available to deployed users. The Node API calls the model, so the model must be reachable **from the API server**:

- The shared Hostinger plan the API runs on **cannot** host a model. Use a VPS (or another machine you control) with the memory above, 4 or more CPU cores, and ideally a GPU. On CPU only, a summary can take 30 to 90 seconds; GoPreach shows the calculated analysis immediately and adds the wording when it arrives.
- Keep Ollama on `127.0.0.1` (its default) when it shares a machine with the API. If it is on another machine, put it behind a private network or a reverse proxy that requires a key, and set `AI_API_KEY`. Never open port 11434 to the internet.

### Install (Linux VPS)

```
curl -fsSL https://ollama.com/install.sh | sh
ollama pull qwen2.5:7b-instruct
ollama run qwen2.5:7b-instruct "Reply with the single word ready"   # smoke test
```

### Configure the API (`backend/.env`, server side only)

```
AI_PROVIDER=ollama                    # or openai-compatible; empty = AI off
AI_URL=http://127.0.0.1:11434
AI_MODEL=qwen2.5:7b-instruct
AI_TIMEOUT_MS=90000
AI_CONTEXT_TOKENS=8192
# AI_API_KEY=...                      # only behind a key-checking proxy
```

Restart the API. The apps never see the endpoint, the model name or the key.

## What is sent to the model

Only the period names, the congregation name, counts, and the figures GoPreach calculated for indicators whose names mean something in the ministry (publishers, pioneers, studies, attendance, totals ...). Any other row label (which could be a person's name), the file name and every other cell are **not** sent; those rows are only counted. Spreadsheet text is passed as quoted data and the instructions say it is never an instruction. Prompts and answers are not logged or stored (the log line holds the status and duration only).

## Limits and protections

- CO sign-in, owner-only access and the congregation grant are checked by the same guard as the rest of the Previous Manual Record; a congregation removed from the grant stops its records being analysed.
- At most 12 analyses per person every 5 minutes.
- Excel limits (file size, worksheets, rows) are those of the import itself.
- 90-second model timeout.

## Failure behaviour (all verified by `backend/test/manual-analysis.test.js`)

| AI result | What the CO sees |
|---|---|
| `OK` | AI wording, labelled with the model name and "checked against GoPreach's calculations" |
| `DISABLED` | "The AI summary is not switched on for this server." Calculated analysis only |
| `UNAVAILABLE`, `TIMEOUT`, `INVALID`, `REJECTED`, `TOO_LARGE` | A clear message, a **Try the AI summary again** button, and the calculated analysis |

The imported Excel record is never changed.

## Not verified

The real model has **not** been run against this code: tests and the browser check use a stand-in server that answers in Ollama's format. Install the model, then open a saved record and press Summary to confirm the green "written by ... model" note appears and its sentences read well.
