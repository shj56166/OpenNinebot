# Cast laboratory common source unit

Shared Android capture, H.264 encoding, frame pacing, visual sampling, power ownership, durable logging and trace storage. The source unit is compiled into each host's Java namespace, so package-private contracts do not become an unnecessarily broad library API.

Host-specific protocol controllers, application policy, credential storage and diagnostic privacy implementations remain in their respective applications. The public export includes this directory by an explicit source allowlist.
