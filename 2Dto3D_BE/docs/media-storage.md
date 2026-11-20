# Product media provider adapter

`CloudinaryMediaStorageService` implements the provider-neutral `MediaStorageService` port. Upload callers pass a `ValidatedMedia` and a server-only `MediaUploadIntent`; the intent derives its public ID from the persisted asset ID and operation UUID. No caller-supplied filename or public ID is sent as the Cloudinary identity.

The adapter passes the validated file's `InputStream` to the Cloudinary SDK rather than materializing the full GLB in an application `byte[]`. Images use `resource_type=image`; GLB files use `resource_type=raw` and the generated public ID ends in `.glb`. Uploads use delivery type `upload`, disable overwrite and filename-based identity, and accept provider metadata only when the returned identity, bytes, format, version, cloud name, resource/delivery types, and HTTPS URL match the request.

Cloudinary's SDK timeouts are configured in milliseconds under `media.storage`: connection establishment, connection-request acquisition, and response/socket wait. The SDK's `timeout` bounds waiting for a response; it is not an absolute wall-clock deadline for an upload that continues making progress. Lifecycle leases and reconciliation must therefore keep an additional safety margin and must not assume these socket bounds impose a total upload duration. See Cloudinary's [upload timeout parameter](https://cloudinary.com/documentation/image_upload_api_reference).

The adapter does not perform compensation or decide whether an asset is referenced. Lifecycle code must call deletion only after a fresh database check proves cleanup is safe. If a provider response is ambiguous or has a mismatched identity, the adapter fails closed and does not delete an identity from that response; durable upload-intent recovery is responsible for later reconciliation.

Designer uploads now use this streaming adapter through the durable Task 5 orchestration. The legacy `CloudinaryServiceImpl` remains in use for the existing AI upload pipeline and still calls `MultipartFile.getBytes()`, buffering each AI upload in application memory. No real Cloudinary verification has been performed.

The public product catalog uses `MediaUrlResolver` for newly linked assets. It returns the persisted `secure_url` only for an `ATTACHED` asset whose provider, configured cloud, kind, resource/delivery types, format, server-generated public ID, version, and exact HTTPS Cloudinary URL path all agree. Legacy rows without a media association continue through the legacy ID resolver. Invalid linked metadata yields a null URL and does not fall back to the legacy path. The catalog remains restricted to active designer products; AI media is not included.

PostgreSQL V1 baseline adoption and V2 migration/concurrency verification, plus real Cloudinary upload/download/delete verification, remain production release gates. No production migration or real provider request is performed by the default test suite.
