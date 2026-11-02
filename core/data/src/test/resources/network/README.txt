Test fixtures for the provider network tests (core/data .../network), made with keytool:
- test-ca.pem: "Locus Test CA", a CA (basicConstraints CA:true), private key discarded.
- server-keystore.bin (PKCS12, password "changeit"; not .p12, which the unit-test resource merge drops): key and chain for CN=localhost (SAN localhost, 127.0.0.1), signed by test-ca.
- server-leaf.pem: that server certificate alone.
- other-ca.pem: "Other CA", an unrelated CA.
Valid for 100 years. Test-only; never trusted by the app.
- impostor-ca.pem: a different key under the same name "CN=Locus Test CA", for a forged-issuer test.
