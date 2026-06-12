package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ImportResult {
    private int importedCount = 0;
    private int skippedCount  = 0;
    private List<String> rowErrors = new ArrayList<>();

    public void incrementImported() { importedCount++; }
    public void incrementSkipped()  { skippedCount++;  }
    public void addError(int row, String msg) {
        rowErrors.add("Row " + row + ": " + msg);
    }
}