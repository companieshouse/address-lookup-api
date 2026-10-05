#!/usr/bin/env perl
use strict;
use warnings;

@ARGV == 1 or die "Usage: report-migration-row-counts.pl <failsafe XML file>\n";
my $xml_path = $ARGV[0];
open my $xml_file, '<', $xml_path
    or die "Could not read Failsafe XML report '$xml_path': $!\n";
local $/;
my $xml = <$xml_file>;
close $xml_file
    or die "Could not close Failsafe XML report '$xml_path': $!\n";

$xml =~ /<testsuite\b[^>]*\btests="(\d+)"[^>]*\berrors="(\d+)"[^>]*\bfailures="(\d+)"/
    or die "Could not read test result summary from '$xml_path'\n";
my ($tests, $errors, $failures) = ($1, $2, $3);
$tests > 0 && $errors == 0 && $failures == 0
    or die "Migration test report is not passing: tests=$tests errors=$errors failures=$failures\n";

my @tables = qw(
    add_gb_builtaddress_v3
    add_isl_builtaddress_v3
    add_gb_royalmailaddress_v1
    add_isl_royalmailaddress_v1
);
my @phases = qw(before-migration after-migration after-liquibase-rerun);
my %results;
while ($xml =~ /Migration row count phase=(before-migration|after-migration|after-liquibase-rerun) sql=\[(SELECT COUNT\(\*\) FROM os_data\.([A-Za-z0-9_]+))\] result=(\d+)/g) {
    my ($phase, $sql, $table, $count) = ($1, $2, $3, 0 + $4);
    exists $results{$phase}{$table}
        and die "Duplicate '$phase' row-count result for '$table' in '$xml_path'\n";
    $results{$phase}{$table} = {
        sql => "$sql;",
        count => $count,
    };
}

for my $phase (@phases) {
    for my $table (@tables) {
        exists $results{$phase}{$table}
            or die "Missing '$phase' row-count result for '$table' in '$xml_path'\n";
    }
}

my %phase_labels = (
    'before-migration' => 'Before moving tables to public',
    'after-migration' => 'After reconciliation back to os_data',
    'after-liquibase-rerun' => 'After rerunning Liquibase',
);
my $report = '';
for my $table (@tables) {
    $report .= "$table\n";
    for my $phase (@phases) {
        my $result = $results{$phase}{$table};
        $report .= "  $phase_labels{$phase}\n";
        $report .= "    SQL executed: $result->{sql}\n";
        $report .= "    rows returned: $result->{count}\n";
    }
    my $before = $results{'before-migration'}{$table}{count};
    for my $phase ('after-migration', 'after-liquibase-rerun') {
        my $difference = $results{$phase}{$table}{count} - $before;
        $report .= "  difference from before ($phase): $difference\n";
    }
    $report .= "\n";
}

my %totals;
for my $phase (@phases) {
    $totals{$phase} += $results{$phase}{$_}{count} for @tables;
}
$report .= "Totals:\n";
for my $phase (@phases) {
    $report .= "  $phase: $totals{$phase}\n";
}

(my $output_path = $xml_path) =~ s/\.xml\z/-row-count-comparison.txt/
    or die "Expected an XML report path ending in .xml, got '$xml_path'\n";
open my $output_file, '>', $output_path
    or die "Could not write row-count comparison '$output_path': $!\n";
print {$output_file} $report
    or die "Could not write row-count comparison '$output_path': $!\n";
close $output_file
    or die "Could not close row-count comparison '$output_path': $!\n";

print $report;
print "Saved comparison to $output_path\n";
