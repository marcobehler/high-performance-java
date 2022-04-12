package com.mycompany.app.model;

public class TaxRate {

    private final String name;
    private final Double rate;

    public TaxRate(String name, Double rate) {
        this.name = name;
        this.rate = rate;
    }

    public String getName() {
        return name;
    }

    public Double getRate() {
        return rate;
    }

    @Override
    public String toString() {
        return "{ \"name\":" + name + ", \"rate\" :  " + rate + "}";
    }
}
