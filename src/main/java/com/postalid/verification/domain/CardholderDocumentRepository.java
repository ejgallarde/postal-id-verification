package com.postalid.verification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CardholderDocumentRepository extends JpaRepository<CardholderDocument, Long> {

    Optional<CardholderDocument> findByCardholderIdAndDocumentTypeCode(Long cardholderId, String docCode);

    List<CardholderDocument> findByCardholderId(Long cardholderId);

    /** Only the codes the media layer is permitted to serve, for one cardholder. */
    List<CardholderDocument> findByCardholderIdAndDocumentTypePubliclyRetrievableTrue(Long cardholderId);
}
