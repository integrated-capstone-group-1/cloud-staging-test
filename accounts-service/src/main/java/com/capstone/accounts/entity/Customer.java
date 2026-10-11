package com.capstone.accounts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customer_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @Column(
        name = "customer_id",
        length = 36,
        nullable = false,
        updatable = false
    )
    private String customerId;

    @Column(name = "first_name", length = 100, nullable = false)
    private String firstName;

    @Column(name = "last_name", length = 100, nullable = false)
    private String lastName;

    @Column(name = "email", length = 255, nullable = false)
    private String email;

    @Column(name = "contact_no", length = 20)
    private String contactNo;

    @Column(name = "birth_date")
    private java.time.LocalDate birthDate;

    @Column(name = "id_type", length = 50)
    private String idType;

    @Column(name = "id_number", length = 100)
    private String idNumber;

    @Column(name = "residential_address", length = 255)
    private String residentialAddress;

    @Builder.Default
    @Column(name = "kyc_status", length = 20, nullable = false)
    private String kycStatus = "PENDING";
}