namespace com.sap.cap.esmlsocustactionset;

using {
    cuid,
    managed
} from '@sap/cds/common';

@readonly
entity EsmOnChangeLogs : cuid, managed {
    caseId : Integer;
    operation : String enum{
        status_change = 'StatusChange';
        external_field_change = 'ExternalFieldChange';
    };
    status : Integer enum{
        none = 0;
        success = 1;
        failure = 2;
    }
}


