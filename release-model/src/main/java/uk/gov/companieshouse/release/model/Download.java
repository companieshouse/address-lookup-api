package uk.gov.companieshouse.release.model;

public record Download(String fileName, String url, long size, String md5) { }
