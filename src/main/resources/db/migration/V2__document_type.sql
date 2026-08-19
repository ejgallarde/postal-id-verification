-- Lookup for the asset-suffix codes observed in an enrollment packet.
--
-- is_publicly_retrievable gates what the API will ever stream. It is TRUE for
-- exactly two codes: the cardholder photo and signature. Fingerprints and the
-- supporting document scans are stored and mapped, but no route can serve them.

CREATE TABLE document_type (
    code                    VARCHAR(16)  NOT NULL PRIMARY KEY,
    description             VARCHAR(120) NOT NULL,
    category                VARCHAR(24)  NOT NULL,
    is_publicly_retrievable BOOLEAN      NOT NULL DEFAULT FALSE
);

INSERT INTO document_type (code, description, category, is_publicly_retrievable) VALUES
    ('Photo',     'Cardholder facial photograph captured at enrollment', 'BIOMETRIC', TRUE),
    ('Signature', 'Cardholder specimen signature captured at enrollment', 'BIOMETRIC', TRUE),
    ('BC',        'Birth certificate scan',                              'DOCUMENT',  FALSE),
    ('MC',        'Marriage certificate scan',                           'DOCUMENT',  FALSE),
    ('OR',        'Official receipt scan',                               'DOCUMENT',  FALSE),
    ('ORForm',    'Official receipt form scan',                          'DOCUMENT',  FALSE),
    -- Meaning not confirmed by the data owner; stored verbatim, never exposed.
    ('PC',        'UNCONFIRMED - supporting document scan (code PC)',    'DOCUMENT',  FALSE),
    ('E6Form',    'UNCONFIRMED - enrollment form scan (code E6Form)',    'DOCUMENT',  FALSE),
    ('RT',        'Right thumb fingerprint (WSQ)',                       'FINGERPRINT', FALSE),
    ('RI',        'Right index fingerprint (WSQ)',                       'FINGERPRINT', FALSE),
    ('LT',        'Left thumb fingerprint (WSQ)',                        'FINGERPRINT', FALSE),
    ('LI',        'Left index fingerprint (WSQ)',                        'FINGERPRINT', FALSE);
