package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import org.springframework.stereotype.Component;

@Component
public class VariableMapper {

    public ProcessVariable toDTO(ProcessVariableEntity entity) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(entity.getName());
        pv.setType(entity.getType());
        pv.setValue(entity.getTextValue());
        return pv;
    }

}
