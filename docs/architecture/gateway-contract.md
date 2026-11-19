# Trusted Chat Completions gateway contract

The Android client sends only to a URL that the user configures and validates. The path must end with `/chat/completions`, normally `https://example.net/v1/chat/completions`. HTTP is accepted by the URI validator only for local loopback tests.

Typical JSON request envelope:

```json
{"model":"your-model-id","stream":false,"messages":[{"role":"system","content":"Translation task and strict contract"},{"role":"user","content":"{\"schema\":\"translation_batch_request_v1\",\"batch_id\":\"...\",\"source_language\":\"zh\",\"target_language\":\"pt\",\"items\":[{\"id\":\"c1\",\"start_ms\":0,\"end_ms\":2500,\"source\":\"你好\"}]}"}]}
```

Return `choices[0].message.content` as a JSON string with matching `batch_id` and item IDs:

```json
{"schema":"translation_batch_result_v1","batch_id":"...","items":[{"id":"c1","status":"ok","translation":"Olá","preserved_names":[]}]}
```

`status` must be `ok` or `uncertain`; uncertain items must use `translation:null`. A proven contiguous fragment may set `continues_previous:true`. Responses are validated strictly before being assigned to timeline positions.

**Security:** use TLS, explicit authentication, server-side model credentials and per-client quotas. The Android setting can carry an optional session-only gateway token; never store a provider master key on the device or in source control. No remote endpoint is active by default.
