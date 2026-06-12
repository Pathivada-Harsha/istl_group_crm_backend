package com.istlgroup.istl_group_crm_backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.istlgroup.istl_group_crm_backend.entity.EmailRequest;
import com.istlgroup.istl_group_crm_backend.service.MailService;



@RestController
@RequestMapping("/mail")
public class MailController {

	@Autowired
	private MailService emailService;
	
	@PostMapping("/send-mail")
	public ResponseEntity<String> sendMail(@RequestBody EmailRequest emailRequest) {
	    try {
	        emailService.sendEmail(
	            emailRequest.getTo(),
	            emailRequest.getSubject(),
	            emailRequest.getBody()
	        );
	        return ResponseEntity.ok("✅ Mail sent successfully to " + emailRequest.getTo());
	    } catch (Exception e) {
	        return ResponseEntity.status(500)
	                .body("❌ Failed to send mail: " + e.getMessage());
	    }
	}
}
