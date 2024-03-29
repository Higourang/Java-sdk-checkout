package com.checkout.payments;

import com.checkout.common.CheckoutUtils;

import java.util.HashMap;

public class AlternativePaymentSource extends HashMap<String, Object> implements RequestSource {
    private static final String TYPE_FIELD = "type";

    public AlternativePaymentSource(String type) {
        if (CheckoutUtils.isNullOrWhitespace(type)) {
            throw new IllegalArgumentException("The alternative payment source type is required.");
        }
        setType(type);
    }

    @Override
    public String getType() {
        return get(TYPE_FIELD).toString();
    }

    public void setType(String type) {
        put(TYPE_FIELD, type);
    }
}