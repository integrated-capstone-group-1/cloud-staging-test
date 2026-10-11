
package com.capstone.common.dto;

import java.time.LocalDate;

/**
 * Mirrors CUSTOMER_MASTER columns.
 * contactNo -> contact_no, birthDate -> birth_date.
 */
public record CustomerDTO(
        String customerId,
        String firstName,
        String lastName,
        String email,
        String contactNo,
        LocalDate birthDate,
        String idType,
        String idNumber,
        String address,
        String kycStatus
) {
    public CustomerDTO(String customerId, String firstName, String lastName, String email, String contactNo, LocalDate birthDate) {
        this(customerId, firstName, lastName, email, contactNo, birthDate, null, null, null, "PENDING");
    }

    public CustomerDTO(
            String customerId,
            String firstName,
            String lastName,
            String email,
            String contactNo,
            LocalDate birthDate,
            String idType,
            String idNumber,
            String address
    ) {
        this(customerId, firstName, lastName, email, contactNo, birthDate, idType, idNumber, address, "PENDING");
    }

    public String residentialAddress() {
        return address;
    }
}

