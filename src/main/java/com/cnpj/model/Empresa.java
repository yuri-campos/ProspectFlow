package com.cnpj.model;

import java.time.LocalDate;

/**
 * Modelo de dados que representa uma empresa extraída da base da Receita Federal.
 */
public class Empresa {

    private String    cnpj;
    private String    razaoSocial;
    private String    telefone;
    private String    email;
    private LocalDate dataAbertura;

    // -----------------------------------------------------------------------
    // Construtores
    // -----------------------------------------------------------------------

    public Empresa() {}

    public Empresa(String cnpj, String razaoSocial, String telefone,
                   String email, LocalDate dataAbertura) {
        this.cnpj         = cnpj;
        this.razaoSocial  = razaoSocial;
        this.telefone     = telefone;
        this.email        = email;
        this.dataAbertura = dataAbertura;
    }

    // -----------------------------------------------------------------------
    // Getters e Setters
    // -----------------------------------------------------------------------

    public String getCnpj() {
        return cnpj;
    }

    public void setCnpj(String cnpj) {
        this.cnpj = cnpj;
    }

    public String getRazaoSocial() {
        return razaoSocial;
    }

    public void setRazaoSocial(String razaoSocial) {
        this.razaoSocial = razaoSocial;
    }

    public String getTelefone() {
        return telefone;
    }

    public void setTelefone(String telefone) {
        this.telefone = telefone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getDataAbertura() {
        return dataAbertura;
    }

    public void setDataAbertura(LocalDate dataAbertura) {
        this.dataAbertura = dataAbertura;
    }

    // -----------------------------------------------------------------------
    // Utilitários
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "Empresa{" +
                "cnpj='"        + cnpj        + '\'' +
                ", razaoSocial='" + razaoSocial + '\'' +
                ", telefone='"    + telefone    + '\'' +
                ", email='"       + email       + '\'' +
                ", dataAbertura=" + dataAbertura +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Empresa)) return false;
        Empresa other = (Empresa) o;
        return cnpj != null && cnpj.equals(other.cnpj);
    }

    @Override
    public int hashCode() {
        return cnpj != null ? cnpj.hashCode() : 0;
    }
}
