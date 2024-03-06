using {com.sap.cap.esmlsocustactionset as db} from '../db/index.cds';

service LogsService {

    entity EsmChangeLogs as projection on db.EsmOnChangeLogs;

}

annotate LogsService with @(requires: 'system-user');