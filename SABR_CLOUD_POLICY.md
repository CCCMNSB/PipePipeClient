# SABR cloud policy delivery

Release builds enable SABR cloud policies with two build environment variables:

- `SABR_POLICY_PUBLIC_KEY_BASE64`: raw 32-byte Ed25519 public key encoded as Base64.
- `SABR_POLICY_URL`: HTTPS endpoint serving the binary envelope accepted by
  `SabrPolicyRuntime.installEnvelope`.

When both values are present, the client restores the last verified policy at startup, requests an
update immediately, and schedules a connected-network refresh every six hours. The endpoint must
return the encoded policy envelope as the response body with HTTP 200. HTTP 204 is treated as no
update. Policies are signature checked, time bounded, and revision monotonic before activation.

If a policy throws while building requests, interpreting responses, routing demanded segments, or
decoding media headers, the client removes that cached policy, retains its highest revision to
prevent rollback, and uses the builtin policy until a higher signed revision is delivered.

Policies that declare `demand: true` can route reader-demand retries and decide how to handle exact
target omissions using bounded, payload-free returned segment identities. Policies without that
declaration retain the builtin demand behavior, so an older valid cloud policy remains compatible
with a client that supports the expanded contract. See the Extractor's
`SABR_JAVASCRIPT_POLICY.md` for the complete event and decision schema.
