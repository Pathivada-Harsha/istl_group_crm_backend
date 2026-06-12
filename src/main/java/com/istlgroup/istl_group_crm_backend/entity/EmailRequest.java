package com.istlgroup.istl_group_crm_backend.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailRequest {

	 private String to;
	 private String subject;
	 private String body;
}
