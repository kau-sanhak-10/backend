package com.ilro.backend.domain.owner.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.DayOfWeek;

// 요일 <-> DB 숫자 1~7 (1=월 ~ 7=일, DayOfWeek.getValue()와 같음)
@Converter
public class DayOfWeekConverter implements AttributeConverter<DayOfWeek, Integer> {

    @Override
    public Integer convertToDatabaseColumn(DayOfWeek dayOfWeek) {
        return dayOfWeek == null ? null : dayOfWeek.getValue();
    }

    @Override
    public DayOfWeek convertToEntityAttribute(Integer value) {
        return value == null ? null : DayOfWeek.of(value);
    }
}
