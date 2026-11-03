#!/usr/bin/env perl
use strict;
use warnings;
use Env qw(HOME);
my $m2 = "$HOME/.m2";
mkdir $m2 if !-d $m2;
my $m2s = "$m2/settings.xml";
if ( !-f $m2s ) {
    printf "JFrog Token: (Get it from https://chartifacts.jfrog.io/ui/packages > Setup A Client > Maven > Select Virtual-Release > Generate token)\n";
    chomp(my $token = <STDIN>);
    open my $fh, '>', $m2s or die "Unable to write to $m2s"; $|=1;
    printf $fh q{<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
 <mirrors>
   <mirror>
     <id>virtual-release</id>
     <url>https://chartifacts.jfrog.io/artifactory/virtual-release</url>
     <mirrorOf>central</mirrorOf>
   </mirror>
 </mirrors>
 <servers>
   <server>
     <id>virtual-release</id>
     <configuration>
       <httpHeaders>
         <property>
           <name>Authorization</name>
           <value>Bearer %s</value>
         </property>
       </httpHeaders>
     </configuration>
   </server>
 </servers>
</settings>}, $token;
}
my $err = "Are you inside the address-lookup-api repo?\n";
my $git = do { open my $fh, '<', '.git/config' or die $err; local $/; <$fh> };
die $err if $git !~/address-lookup-api\.git/;
chmod 0600, $m2s;
my $xml = do { open my $fh, '<', $m2s; local $/; <$fh> };
my $tag = qr/(?:password|value)/;
my ($token) = $xml =~ m{<$tag>([^<]+)</$tag>};
$token =~s/^Bearer //g;
my @cmd = qw( mvn -pl address-lookup-api org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree );
print "@cmd\n";
exec @cmd;
