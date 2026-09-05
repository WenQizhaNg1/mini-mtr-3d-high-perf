package dev.minimtr.service;

import dev.minimtr.model.dto.TimetableTrip;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.springframework.stereotype.Service;
import static dev.minimtr.service.DatasetParser.require;

@Service
public class TimetableCsv {
    public record Input(String content,Map<String,String> fields) {}
    public List<TimetableTrip> parse(Input input) {
        require(input.content()!=null && input.content().length()<=5*1024*1024,"CSV exceeds 5 MiB");
        var fields=input.fields()==null?Map.<String,String>of():input.fields();
        record Key(String trip,LocalDate date) {}
        var patterns=new LinkedHashMap<Key,String>();
        var calls=new LinkedHashMap<Key,List<TimetableTrip.Call>>();
        try(var csv=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(new StringReader(input.content().replaceFirst("^\\uFEFF","")))) {
            for(String key:List.of("trip","pattern","seq","arrival","departure"))
                require(csv.getHeaderNames().contains(fields.getOrDefault(key,key)),"Missing CSV column: "+key);
            int count=0;
            for(var row:csv) {
                require(++count<=10000,"CSV exceeds 10000 rows");
                String trip=row.get(fields.getOrDefault("trip","trip"));
                String pattern=row.get(fields.getOrDefault("pattern","pattern"));
                require(!trip.isBlank() && !pattern.isBlank(),"Trip and pattern required at row "+row.getRecordNumber());
                String dateField=fields.getOrDefault("serviceDate","serviceDate");
                String date=csv.getHeaderNames().contains(dateField)?row.get(dateField):"";
                var key=new Key(trip,date.isBlank()?null:LocalDate.parse(date));
                require(!patterns.containsKey(key) || patterns.get(key).equals(pattern),"Trip has multiple patterns: "+trip);
                patterns.put(key,pattern);
                String arrival=row.get(fields.getOrDefault("arrival","arrival"));
                String departure=row.get(fields.getOrDefault("departure","departure"));
                calls.computeIfAbsent(key,k -> new ArrayList<>()).add(new TimetableTrip.Call(
                        Integer.parseInt(row.get(fields.getOrDefault("seq","seq"))),arrival.isBlank()?null:arrival,departure.isBlank()?null:departure));
            }
        } catch(org.springframework.web.server.ResponseStatusException error) { throw error; }
        catch(Exception error) { throw DatasetParser.bad("Cannot read timetable CSV: "+error.getMessage()); }
        return calls.entrySet().stream().map(e -> new TimetableTrip(e.getKey().trip(),patterns.get(e.getKey()),e.getKey().date()==null?null:e.getKey().date().toString(),
                e.getValue().stream().sorted(Comparator.comparingInt(TimetableTrip.Call::seq)).toList())).toList();
    }
}
