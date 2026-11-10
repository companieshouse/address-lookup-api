locals {
  name_prefix = "address-lookup-${var.environment}"
  tags        = merge(module.iac_tags.tags, module.owner_tags.tags)

  # Reads, writes and listings are scoped independently for each execution role.
  lambda_access = {
    discovery = {
      read  = ["control/address-release/active.json"]
      write = ["control/address-release/active.json", "control/acquisitions/*"]
      list  = []
      scan  = []
    }
    download = {
      read  = ["control/acquisitions/*", "control/receipts/*/*/download.json"]
      write = ["landing/raw/*", "control/receipts/*/*/download.json", "control/checksums/raw/*"]
      list  = ["control/acquisitions/*", "control/receipts/*/*/download.json"]
      scan  = []
    }
    extraction = {
      read  = ["landing/raw/*", "landing/extracted/*", "control/receipts/*/*/extract.json", "control/checksums/extracted/*"]
      write = ["landing/extracted/*", "control/receipts/*/*/extract.json", "control/checksums/extracted/*"]
      list  = ["control/receipts/*/*/extract.json", "control/checksums/extracted/*"]
      scan  = ["landing/raw/*"]
    }
    upload = {
      read  = ["control/acquisitions/*", "control/ready/*", "control/reconciliation/imports.json", "landing/extracted/*"]
      write = ["control/reconciliation/imports.json"]
      list  = ["control/acquisitions/*", "control/ready/*", "control/reconciliation/imports.json"]
      scan  = ["landing/extracted/*"]
    }
    quarantine = {
      read  = ["landing/raw/*", "landing/extracted/*"]
      write = ["quarantine/*"]
      list  = []
      scan  = ["landing/raw/*", "landing/extracted/*"]
    }
  }
}
