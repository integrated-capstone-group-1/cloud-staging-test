package com.capstone.accounts.repository;

import com.capstone.accounts.entity.PostingReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostingReferenceRepository extends JpaRepository<PostingReference, String> {

    Optional<PostingReference> findByPostingReferenceId(String postingReferenceId);

    Optional<PostingReference> findByCoreReferenceId(String coreReferenceId);
}
