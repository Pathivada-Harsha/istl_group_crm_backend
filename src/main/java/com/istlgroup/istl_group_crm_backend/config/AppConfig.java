package com.istlgroup.istl_group_crm_backend.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_config")
public class AppConfig {

    @Id
    @Column(name = "config_key")
    private String configKey;

    @Column(name = "config_value", nullable = false)
    private String configValue;

    @Column(name = "description")
    private String description;

    public String getConfigKey()             { return configKey; }
    public void setConfigKey(String k)       { this.configKey = k; }

    public String getConfigValue()           { return configValue; }
    public void setConfigValue(String v)     { this.configValue = v; }

    public String getDescription()           { return description; }
    public void setDescription(String d)     { this.description = d; }
}