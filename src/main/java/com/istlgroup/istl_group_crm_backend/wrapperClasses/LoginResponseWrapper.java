package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.time.Duration;
import java.util.List;
import java.util.Map;


import lombok.Data;

@Data
public class LoginResponseWrapper {

	private LoginCredentialsWrapper user;
   
	private List<String> menuPermissions;
	private Map<String, List<String>> pagePermissions;
	
	private Long sessionTimeout;

	private Long warningTime;

}
