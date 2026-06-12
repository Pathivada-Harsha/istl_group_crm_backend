package com.istlgroup.istl_group_crm_backend.service;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;

@Service
public class MailService {

	
	@Autowired
	private JavaMailSender mailSender;

	@Async
	public void sendEmail(String to, String subject, String body) {

	    try {
	        MimeMessage message = mailSender.createMimeMessage();
	        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

	        helper.setFrom("SESOLA CRM <istlgroup@zohomail.in>");
	        helper.setTo(to);
	        helper.setSubject(subject);

	        // true = HTML content enabled
	        helper.setText(body, true);

	        mailSender.send(message);

	    } catch (Exception e) {
	        throw new RuntimeException("Error while sending email: " + e.getMessage());
	    }
	}
}
